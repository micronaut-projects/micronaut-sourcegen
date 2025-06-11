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

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Experimental;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.inject.ast.ArrayableClassElement;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.PrimitiveElement;
import io.micronaut.inject.ast.PropertyElement;
import io.micronaut.inject.visitor.VisitorContext;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * The class definition.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Experimental
public final class RecordDef extends ObjectDef {

    private final List<TypeDef.TypeVariable> typeVariables;

    private RecordDef(ClassTypeDef.ClassName className,
                      EnumSet<Modifier> modifiers,
                      List<MethodDef> methods,
                      List<PropertyDef> properties,
                      List<AnnotationDef> annotations,
                      List<String> javadoc,
                      List<TypeDef.TypeVariable> typeVariables,
                      List<TypeDef> superinterfaces,
                      List<ObjectDef> innerTypes,
                      boolean synthetic) {
        super(className, modifiers, annotations, javadoc, methods, properties, superinterfaces, innerTypes, synthetic);
        this.typeVariables = typeVariables;
    }

    @Override
    public RecordDef withClassName(ClassTypeDef.ClassName className) {
        return new RecordDef(className, modifiers, methods, properties, annotations, javadoc, typeVariables, superinterfaces, innerTypes, synthetic);
    }

    public static RecordDefBuilder builder(String name) {
        return new RecordDefBuilder(name);
    }

    public List<TypeDef.TypeVariable> getTypeVariables() {
        return typeVariables;
    }

    /**
     * Turn the record components into bean properties.
     * @param visitorContext The visitor context.
     * @return The property elements.
     */
    public List<PropertyElement> getBeanProperties(VisitorContext visitorContext) {
        List<PropertyElement> propertyElements = new ArrayList<>(properties.size());
        for (PropertyDef property : properties) {
            MutableAnnotationMetadata annotationMetadata = toAnnotationMetadata(property);
            propertyElements.add(new PropertyElement() {
                @Override
                public @NonNull ClassElement getType() {
                    TypeDef type = property.getType();
                    return toClassElement(type, visitorContext);
                }

                @Override
                public ClassElement getDeclaringType() {
                    return ClassElement.of(className.getCanonicalName());
                }

                @Override
                public @NonNull String getName() {
                    return property.getName();
                }

                @Override
                public boolean isProtected() {
                    return false;
                }

                @Override
                public boolean isPublic() {
                    return true;
                }

                @Override
                public @NonNull Object getNativeType() {
                    return property;
                }

                @Override
                public @NonNull AnnotationMetadata getAnnotationMetadata() {
                    return annotationMetadata;
                }

                @Override
                public AccessKind getReadAccessKind() {
                    return AccessKind.METHOD;
                }
            });
        }
        return Collections.unmodifiableList(propertyElements);
    }

    private static MutableAnnotationMetadata toAnnotationMetadata(PropertyDef property) {
        MutableAnnotationMetadata annotationMetadata = new MutableAnnotationMetadata();
        for (AnnotationDef annotation : property.annotations) {
            annotationMetadata.addDeclaredAnnotation(annotation.getType().getName(), new LinkedHashMap<>(annotation.getValues()));
        }
        return annotationMetadata;
    }

    /**
     * Turn the record components into constructor parameters.
     * @param visitorContext The visitor context.
     * @return The constructor parameters.
     */
    public List<ParameterElement> getConstructorParameters(VisitorContext visitorContext) {
        List<ParameterElement> propertyElements = new ArrayList<>(properties.size());
        for (PropertyDef property : properties) {
            MutableAnnotationMetadata annotationMetadata = toAnnotationMetadata(property);
            propertyElements.add(new ParameterElement() {
                @Override
                public @NonNull ClassElement getType() {
                    TypeDef type = property.getType();
                    return toClassElement(type, visitorContext);
                }

                @Override
                public @NonNull String getName() {
                    return property.getName();
                }

                @Override
                public boolean isProtected() {
                    return false;
                }

                @Override
                public boolean isPublic() {
                    return true;
                }

                @Override
                public @NonNull Object getNativeType() {
                    return property;
                }

                @Override
                public @NonNull AnnotationMetadata getAnnotationMetadata() {
                    return annotationMetadata;
                }
            });
        }
        return Collections.unmodifiableList(propertyElements);
    }

    private static ClassElement toClassElement(TypeDef type, VisitorContext visitorContext) {
        if (type instanceof TypeDef.Primitive primitive) {
            return PrimitiveElement.valueOf(primitive.name());
        } else if (type instanceof ClassTypeDef.ClassElementType cet) {
            return cet.classElement();
        } else if (type instanceof ClassTypeDef.JavaClass javaClass) {
            return visitorContext.getClassElement(javaClass.getName())
                .orElseGet(() -> ClassElement.of(javaClass.type()));
        } else if (type instanceof ClassTypeDef.ClassName javaClass) {
            return visitorContext.getClassElement(javaClass.getName())
                .orElseGet(() -> ClassElement.of(javaClass.getName()));
        } else if (type instanceof ClassTypeDef.ClassDefType classDefType) {
            return visitorContext.getClassElement(classDefType.getName())
                .orElseGet(() -> ClassElement.of(classDefType.getName()));
        } else if (type instanceof ClassTypeDef.Parameterized parameterized) {
            ClassTypeDef rawType = parameterized.rawType();
            if (rawType instanceof ClassTypeDef.ClassElementType cet) {
                return cet.classElement();
            } else {
                ClassElement classElement = toClassElement(rawType, visitorContext);
                return classElement.withTypeArguments(
                    parameterized.typeArguments().stream().map(tf -> toClassElement(tf, visitorContext)).toList()
                );
            }

        } else if (type instanceof TypeDef.Array array) {
            TypeDef typeDef = array.componentType();
            ClassElement componentType = toClassElement(typeDef, visitorContext);
            if (componentType instanceof ArrayableClassElement arrayableClassElement) {
                return arrayableClassElement.withArrayDimensions(array.dimensions());
            }
        } else if (type instanceof TypeDef.AnnotatedTypeDef annotated) {
            ClassElement element = toClassElement(annotated.typeDef(), visitorContext);
            for (AnnotationDef annotation: annotated.annotations()) {
                element.annotate(annotation.toAnnotationValue());
            }
            return element;
        } else if (type instanceof ClassTypeDef.AnnotatedClassTypeDef annotated) {
            ClassElement element = toClassElement(annotated.typeDef(), visitorContext);
            for (AnnotationDef annotation: annotated.annotations()) {
                element.annotate(annotation.toAnnotationValue());
            }
            return element;
        }
        throw new IllegalStateException("Only properties constructed from source elements are supported");
    }

    /**
     * The record definition builder.
     *
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    public static final class RecordDefBuilder extends ObjectDefBuilder<RecordDefBuilder> {

        private final List<TypeDef.TypeVariable> typeVariables = new ArrayList<>();

        private RecordDefBuilder(String name) {
            super(name);
        }

        public RecordDefBuilder addTypeVariable(TypeDef.TypeVariable typeVariable) {
            typeVariables.add(typeVariable);
            return this;
        }

        public RecordDef build() {
            return new RecordDef(new ClassTypeDef.ClassName(name), modifiers, methods, properties, annotations, javadoc, typeVariables, superinterfaces, innerTypes, synthetic);
        }

    }

}
